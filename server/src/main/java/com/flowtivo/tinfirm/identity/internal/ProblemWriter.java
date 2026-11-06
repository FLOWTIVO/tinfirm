package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Writes an {@link ApiException} from a servlet filter as the same problem detail the controllers produce, through
 * the MVC exception handling (as {@link ProblemSecurityHandlers} does for the session chain).
 */
final class ProblemWriter {

    private final HandlerExceptionResolver resolver;

    ProblemWriter(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    void write(HttpServletRequest request, HttpServletResponse response, ApiException problem) throws IOException {
        if (resolver.resolveException(request, response, null, problem) == null) {
            response.sendError(problem.status().value());
        }
    }
}
