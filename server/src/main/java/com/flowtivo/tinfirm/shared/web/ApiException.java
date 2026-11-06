package com.flowtivo.tinfirm.shared.web;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * An expected API error, rendered as an RFC 9457 problem detail by {@link ApiExceptionHandler}.
 * The detail message is sent to the client, so it must never contain secret values or credentials.
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final @Nullable ProblemCode code;

    private final @Nullable Duration retryAfter;

    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, @Nullable ProblemCode code, String detail) {
        this(status, code, detail, null, Map.of());
    }

    private ApiException(HttpStatus status, @Nullable ProblemCode code, String detail, @Nullable Duration retryAfter,
            Map<String, Object> properties) {
        super(detail, null, false, false);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.properties = Map.copyOf(properties);
    }

    public static ApiException validation(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, detail);
    }

    /**
     * {@code VALIDATION_FAILED} for fields of the request, each in {@code errors} as {@code {field, message}} with the
     * detail as its message: the shape a request's schema validation answers ({@link ApiExceptionHandler}), so that a
     * form marks the field.
     */
    public static ApiException invalid(String detail, String... fields) {
        return badRequest(ProblemCode.VALIDATION_FAILED, detail, fieldErrors(detail, fields));
    }

    /** The {@code errors} property of a problem, one per field, each with the message. */
    public static Map<String, Object> fieldErrors(String message, String... fields) {
        return Map.of("errors", java.util.Arrays.stream(fields)
            .map(field -> Map.of("field", field, "message", message))
            .toList());
    }

    public static ApiException badRequest(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    /**
     * 400 with extra properties of the problem detail, added as they are (for example {@code details} with the
     * {@code systemId} of {@code ACCOUNT_NOT_ENTITLED}); like the detail, they must never hold secrets.
     */
    public static ApiException badRequest(ProblemCode code, String detail, Map<String, Object> properties) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail, null, properties);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, null, detail);
    }

    public static ApiException unauthorized(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, null, detail);
    }

    public static ApiException forbidden(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    /**
     * 403 with extra properties of the problem detail, added as they are (for example {@code details} with the
     * {@code feature} of {@code EDITION_REQUIRED}, ADR-0077); like the detail, they must never hold secrets.
     */
    public static ApiException forbidden(ProblemCode code, String detail, Map<String, Object> properties) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail, null, properties);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, null, detail);
    }

    public static ApiException notFound(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException conflict(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    /**
     * 409 with extra properties of the problem detail, added as they are (for example {@code details} with the
     * {@code connections} of {@code ACCOUNTS_REQUIRED}, ADR-0049); like the detail, they must never hold secrets.
     */
    public static ApiException conflict(ProblemCode code, String detail, Map<String, Object> properties) {
        return new ApiException(HttpStatus.CONFLICT, code, detail, null, properties);
    }

    public static ApiException payloadTooLarge(ProblemCode code, String detail) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, code, detail);
    }

    /**
     * 422: the request is well-formed, but its content is refused. {@code properties} are added to the problem
     * detail as they are (for example {@code credentialMatches}); like the detail, they must never hold secrets.
     */
    public static ApiException unprocessable(ProblemCode code, String detail, Map<String, Object> properties) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail, null, properties);
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ProblemCode.RATE_LIMITED,
                "Too many attempts. Try again later.", retryAfter, Map.of());
    }

    /** 429 {@code RATE_LIMITED} with its own detail, and {@code Retry-After}. */
    public static ApiException rateLimited(Duration retryAfter, String detail) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ProblemCode.RATE_LIMITED, detail, retryAfter, Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public @Nullable ProblemCode code() {
        return code;
    }

    public @Nullable Duration retryAfter() {
        return retryAfter;
    }

    /** Extra properties of the problem detail. */
    public Map<String, Object> properties() {
        return properties;
    }
}
