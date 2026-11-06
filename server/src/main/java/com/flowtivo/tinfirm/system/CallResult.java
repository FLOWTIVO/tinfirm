package com.flowtivo.tinfirm.system;

import java.time.Duration;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The result of a call to a system: a value, or why it failed. Failures are returned, never thrown, and never contain
 * a token or a secret.
 */
public record CallResult<T>(@Nullable T value, @Nullable Failure failure) {

    /**
     * Why a call to a system failed, as a value.
     *
     * @param retryAfter how long to wait when the system said so, else null
     */
    public record Failure(Kind kind, String message, @Nullable Duration retryAfter) {

        public static Failure of(Kind kind, String message) {
            return new Failure(kind, message, null);
        }

        /** Whether trying again later may help: the rate limit or the system's availability. */
        public boolean retryable() {
            return kind == Kind.RATE_LIMITED || kind == Kind.UNAVAILABLE;
        }
    }

    /** What went wrong, for the caller's decision: give up, try later, or tell a person. */
    public enum Kind {
        /**
         * The credential was refused (401, an OAuth {@code invalid_grant}; a 403 too where a credential is checked,
         * such as a "who am I").
         */
        UNAUTHORIZED,
        /**
         * The credential was taken, and the system does not let its account do this (a 403 to a tool's call or a
         * change request, the package 3 contract §5.4 step 6).
         */
        FORBIDDEN,
        /** The endpoint does not exist (404). */
        NOT_FOUND,
        /** The system's rate limit is exhausted (429). */
        RATE_LIMITED,
        /** The system could not be reached or answered 5xx; trying again later may work. */
        UNAVAILABLE,
        /** The request or the system's settings are wrong: a 3xx, 400 or 422, an unusable URL, a bad answer. */
        INVALID,
        /** Anything else. */
        OTHER
    }

    public static <T> CallResult<T> ok(T value) {
        return new CallResult<>(value, null);
    }

    public static <T> CallResult<T> failed(Failure failure) {
        return new CallResult<>(null, failure);
    }

    public static <T> CallResult<T> failed(Kind kind, String message) {
        return failed(Failure.of(kind, message));
    }

    public boolean succeeded() {
        return failure == null;
    }

    /** The failure's message, or null on success. */
    public @Nullable String failureMessage() {
        return failure != null ? failure.message() : null;
    }

    /** The same failure with another value type, for passing a failure on. */
    public <R> CallResult<R> failedAs() {
        if (failure == null) {
            throw new IllegalStateException("not a failure");
        }
        return failed(failure);
    }

    public <R> CallResult<R> map(Function<? super T, ? extends R> mapper) {
        return value != null ? ok(mapper.apply(value)) : failedAs();
    }
}
