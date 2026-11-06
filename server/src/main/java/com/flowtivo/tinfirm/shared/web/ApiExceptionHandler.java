package com.flowtivo.tinfirm.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code application/problem+json} with the {@code code} property of CONVENTIONS §6.
 * <p>
 * Validation errors list the failing fields and messages, never the rejected values, so request content such as
 * passwords or secret values cannot be echoed back. The security filter chain delegates its 401 and 403 responses
 * here as well.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String CODE = "code";

    private static final String ERRORS = "errors";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        if (ex.code() != null) {
            problem.setProperty(CODE, ex.code().name());
        }
        ex.properties().forEach(problem::setProperty);
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())));
        }
        return ResponseEntity.status(ex.status()).headers(headers).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        String detail = ex instanceof CsrfException ? "Missing or invalid CSRF token" : "Access denied";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations()
            .stream()
            .map(violation -> fieldError(violation.getPropertyPath().toString(), violation.getMessage()))
            .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(fieldError(error.getField(), message(error)));
        }
        ex.getBindingResult()
            .getGlobalErrors()
            .forEach(error -> errors.add(fieldError(error.getObjectName(), message(error))));
        return ResponseEntity.badRequest().headers(headers).body(validationProblem(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> errors.add(fieldError(name, message(error))));
        });
        return ResponseEntity.badRequest().headers(headers).body(validationProblem(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser message may quote request content: never echo it.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /**
     * A multipart upload over {@code spring.servlet.multipart.max-file-size} (a conversation's files, ADR-0014; a
     * runner host's upload of {@code /output}).
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The file is larger than the server allows");
        problem.setProperty(CODE, ProblemCode.ATTACHMENT_TOO_LARGE.name());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value() && body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(CODE))) {
            problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        problem.setProperty(ERRORS, errors);
        return problem;
    }

    private static Map<String, String> fieldError(@Nullable String field, @Nullable String message) {
        return Map.of("field", field != null ? field : "", "message", message != null ? message : "is invalid");
    }

    private static @Nullable String message(MessageSourceResolvable error) {
        return error.getDefaultMessage();
    }
}
