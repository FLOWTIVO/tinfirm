package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.SizeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.util.unit.DataSize;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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

    /**
     * The request attribute a handler sets once it authenticated the caller with a credential that the security
     * chain does not check, so that a refused upload is read to its end ({@link #drain}).
     */
    public static final String AUTHENTICATED_CALLER = ApiExceptionHandler.class.getName() + ".authenticatedCaller";

    private final DataSize maxDrainSize;

    ApiExceptionHandler(@Value("${tinfirm.attachment.max-drain-size:64MB}") DataSize maxDrainSize) {
        this.maxDrainSize = maxDrainSize;
    }

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

    /**
     * Tomcat could not parse the request's parameters, from the query string or a form body: a value that is not
     * valid UTF-8, a malformed percent-escape, more parameters than {@code maxParameterCount}, or a form body larger
     * than {@code maxPostSize}. Servlet 6.1 lets {@code getParameter} throw instead of dropping the parameter. The
     * status is Tomcat's, 400 or 413. The message quotes the parameter's name and value, so it is never sent or logged.
     * {@link RequestInputFilter} brings here the same exception from a servlet filter.
     */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemDetail> handleInvalidParameter(InvalidParameterException ex, WebRequest request) {
        if (ex.getCause() instanceof SizeException) {
            // A multipart over its limit, found when a parameter was read before the parts (a runner host's upload).
            return uploadTooLarge(request);
        }
        if (ex.getErrorCode() == HttpStatus.CONTENT_TOO_LARGE.value()) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                        "The request's parameters are larger than the server allows"));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request's parameters cannot be read: a value is not valid UTF-8, an escape is malformed, "
                        + "or there are too many");
        problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A multipart body Tomcat cannot parse (a broken boundary or part header). A body over the multipart limit is
     * {@link #handleMaxUploadSizeExceededException}'s. Spring MVC wraps what the parts' reader threw, such as
     * {@link NulRefusingRequest}'s refusal, which is answered as itself. The message may quote the body: never sent.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> handleMultipart(MultipartException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException refusal) {
                return handleApiException(refusal);
            }
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed multipart body");
        problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        // The rule of the answers after a commit and for want of a connection (ServerBusy); else as it always was.
        ServerBusy.Answer answer = ServerBusy.decide(ex, request.getMethod());
        if (answer != ServerBusy.Answer.NONE) {
            return handleApiException(ServerBusy.exception(answer, ex));
        }
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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                nul(ex) ? NulCharacterException.DETAIL : "Malformed request body");
        problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /**
     * A multipart upload over {@code spring.servlet.multipart.max-file-size} or {@code max-request-size} (a
     * conversation's files, ADR-0014; a skill's upload; the MCP endpoint's files). A runner host's upload of
     * {@code /output} reads a parameter before the part, so Tomcat's refusal comes as an
     * {@link InvalidParameterException} instead ({@link #handleInvalidParameter}), answered the same way. The rest
     * of the body is read and thrown away first ({@link #drain}).
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers)
            .body(uploadTooLarge(request).getBody());
    }

    private ResponseEntity<ProblemDetail> uploadTooLarge(WebRequest request) {
        drain(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The file is larger than the server allows");
        problem.setProperty(CODE, ProblemCode.ATTACHMENT_TOO_LARGE.name());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }

    /**
     * Every answer of the base class passes here. One it would give as a 5xx goes through the rule of the answers for
     * want of a database connection and after a commit ({@link ServerBusy}) first: the session's save when the
     * response is about to be committed runs inside the write of an answer larger than the response's buffer, and its
     * failure arrives as an {@code HttpMessageNotWritableException}. The dispatcher has reset the buffer before, so
     * nothing of the unfinished answer goes out.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            HttpServletRequest servlet = request instanceof NativeWebRequest nativeRequest
                    ? nativeRequest.getNativeRequest(HttpServletRequest.class) : null;
            ServerBusy.Answer answer = ServerBusy.decide(ex, servlet != null ? servlet.getMethod() : null);
            if (answer != ServerBusy.Answer.NONE) {
                ResponseEntity<ProblemDetail> busy = handleApiException(ServerBusy.exception(answer, ex));
                HttpHeaders answerHeaders = new HttpHeaders();
                answerHeaders.putAll(busy.getHeaders());
                return super.handleExceptionInternal(ex, busy.getBody(), answerHeaders, busy.getStatusCode(), request);
            }
        }
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value() && body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(CODE))) {
            problem.setProperty(CODE, ProblemCode.VALIDATION_FAILED.name());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /**
     * Reads the rest of a refused upload and throws it away, when the request declares at most
     * {@code tinfirm.attachment.max-drain-size} ({@code TINFIRM_ATTACHMENT_MAX_DRAIN_SIZE}) and its caller is
     * authenticated. Tomcat refuses an upload over the multipart limit by its declared length, before it reads the
     * body, and after the answer it reads at most {@code maxSwallowSize} (2 MB) of what is left before it closes the
     * connection. A client that is still sending then gets a reset instead of the 413, and so does a reverse proxy
     * that passes the body on as it comes. Read here, the body goes nowhere: not into memory beyond one buffer, and not
     * onto the disk.
     * <p>
     * Authenticated means: the servlet request has a user principal, which Spring Security's chains give a signed-in
     * person and a bearer token (an anonymous caller has none), or the handler marked it {@link #AUTHENTICATED_CALLER}
     * after it checked a credential of its own (a runner host's token). A refusal that {@link RequestInputFilter}
     * brings from a filter, outside the security chains, has neither: such an upload, like every anonymous one, is
     * closed after Tomcat's 2 MB as before. So is a larger upload, or one without a declared length beyond the bound.
     */
    private void drain(WebRequest request) {
        HttpServletRequest servlet = request instanceof NativeWebRequest nativeRequest
                ? nativeRequest.getNativeRequest(HttpServletRequest.class) : null;
        long bound = maxDrainSize.toBytes();
        if (servlet == null || servlet.getContentLengthLong() > bound
                || servlet.getUserPrincipal() == null && servlet.getAttribute(AUTHENTICATED_CALLER) == null) {
            return;
        }
        try {
            InputStream body = servlet.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            int n;
            while (read <= bound && (n = body.read(buffer)) >= 0) {
                read += n;
            }
        }
        catch (IOException | IllegalStateException ex) {
            // The client went away, or a reader holds the body: Tomcat closes the connection after the answer.
            log.debug("The rest of a refused upload was not read: {}", ex.toString());
        }
    }

    /** Whether the body was refused for U+0000 while it was read ({@link NulRefusingRequest}). */
    private static boolean nul(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof NulCharacterException) {
                return true;
            }
        }
        return false;
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
