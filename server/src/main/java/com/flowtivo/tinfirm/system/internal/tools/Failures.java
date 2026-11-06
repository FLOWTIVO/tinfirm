package com.flowtivo.tinfirm.system.internal.tools;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

/**
 * How a tracker's refusal becomes a {@link CallResult.Failure}: the status's kind, where a tool call tells a refused
 * credential ({@code 401}, {@code UNAUTHORIZED}: the account must be connected again) from an account that may not do
 * this ({@code 403}, {@code FORBIDDEN}), and the system's own words, cut to 300 characters.
 */
public final class Failures {

    /** A failure's message is cut to this many characters. */
    public static final int MAX_MESSAGE_LENGTH = 300;

    private Failures() {
    }

    /** The kind of a status that is not 2xx, for a tool call. */
    public static CallResult.Kind kindOf(int status) {
        return switch (status) {
            case 401 -> CallResult.Kind.UNAUTHORIZED;
            case 403 -> CallResult.Kind.FORBIDDEN;
            default -> OutboundHttp.kindOf(status);
        };
    }

    /**
     * The failure of a status: {@code "{system} answered {status}"}, a redirect's hint, the system's messages, and
     * for a {@code 429} the wait its {@code Retry-After} names.
     *
     * @param rateLimited whether the system said its rate limit is the reason (GitHub's {@code 403} with its headers)
     */
    public static CallResult.Failure of(String system, OutboundHttp.Response response, List<String> messages,
            boolean rateLimited, @Nullable Duration retryAfter) {
        int status = response.status();
        StringBuilder reason = new StringBuilder(system).append(" answered ").append(status);
        if (status >= 300 && status < 400) {
            reason.append(", a redirect: check the system's base URL");
        }
        Duration wait = retryAfter;
        if (status == 429 || rateLimited) {
            reason.append(", the rate limit is exhausted");
            if (wait == null) {
                wait = response.header("Retry-After").map(OutboundHttp::retryAfter).orElse(null);
            }
            if (wait != null) {
                reason.append("; try again in ").append(Math.max(1, wait.toSeconds())).append(" s");
            }
        }
        List<String> said = messages.stream().filter(message -> message != null && !message.isBlank()).toList();
        if (!said.isEmpty()) {
            reason.append(": ").append(String.join("; ", said));
        }
        CallResult.Kind kind = rateLimited || status == 429 ? CallResult.Kind.RATE_LIMITED : kindOf(status);
        return new CallResult.Failure(kind, Json.cut(reason.toString(), MAX_MESSAGE_LENGTH),
                kind == CallResult.Kind.RATE_LIMITED && wait != null && !wait.isNegative() ? wait : null);
    }
}
