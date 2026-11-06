package com.flowtivo.tinfirm.company.scim;

import java.io.Serial;

import org.jspecify.annotations.Nullable;

/**
 * A refusal of the SCIM endpoint, answered in SCIM's own form (RFC 7644 §3.12: {@code schemas}, {@code status},
 * {@code scimType}, {@code detail}), never {@code problem+json} (ADR-0087, decision 1).
 */
public class ScimException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    private final @Nullable String scimType;

    private final transient @Nullable Long retryAfterSeconds;

    public ScimException(int status, @Nullable String scimType, String detail) {
        this(status, scimType, detail, null);
    }

    private ScimException(int status, @Nullable String scimType, String detail, @Nullable Long retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ScimException invalidValue(String detail) {
        return new ScimException(400, "invalidValue", detail);
    }

    public static ScimException invalidFilter(String detail) {
        return new ScimException(400, "invalidFilter", detail);
    }

    public static ScimException invalidSyntax(String detail) {
        return new ScimException(400, "invalidSyntax", detail);
    }

    public static ScimException invalidPath(String detail) {
        return new ScimException(400, "invalidPath", detail);
    }

    public static ScimException mutability(String detail) {
        return new ScimException(400, "mutability", detail);
    }

    public static ScimException uniqueness(String detail) {
        return new ScimException(409, "uniqueness", detail);
    }

    public static ScimException notFound(String detail) {
        return new ScimException(404, null, detail);
    }

    /** {@code 403} with the detail {@code EDITION_REQUIRED}: the licence does not serve SCIM (decision 10). */
    public static ScimException editionRequired() {
        return new ScimException(403, null, "EDITION_REQUIRED");
    }

    public static ScimException tooManyRequests(long retryAfterSeconds) {
        return new ScimException(429, null, "Too many requests", Math.max(1, retryAfterSeconds));
    }

    public int status() {
        return status;
    }

    public @Nullable String scimType() {
        return scimType;
    }

    public @Nullable Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
