package com.flowtivo.tinfirm.company.internal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The SCIM endpoint's refusals in SCIM's own form (RFC 7644 §3.12), never {@code problem+json} (ADR-0087, decision
 * 1): for {@link ScimController} only, before the API's handler.
 */
@RestControllerAdvice(assignableTypes = ScimController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ScimExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);

    @ExceptionHandler(ScimException.class)
    ResponseEntity<Map<String, Object>> scim(ScimException ex) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() != null) {
            answer.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return answer.body(ScimErrors.body(ex));
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class })
    ResponseEntity<Map<String, Object>> unreadable(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScimRequestFilter.BodyTooLarge tooLarge) {
                // A body without a declared length that ran past the filter's cap, found while it was parsed.
                return scim(new ScimException(413, null, tooLarge.getMessage()));
            }
        }
        return scim(ScimException.invalidSyntax("The request is not SCIM's JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return scim(new ScimException(415, null, "Send application/scim+json or application/json"));
    }

    /** A refusal of the core's in the API's form, answered in SCIM's: its status and its message. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex) {
        String code = ex.code() != null ? ex.code().name() : ex.getMessage();
        return scim(new ScimException(ex.status().value(), null, code));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unexpected(RuntimeException ex) {
        log.error("A SCIM request failed", ex);
        return scim(new ScimException(500, null, "The request failed in Tinfirm"));
    }
}
