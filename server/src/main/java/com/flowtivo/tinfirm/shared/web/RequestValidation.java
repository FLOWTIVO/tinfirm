package com.flowtivo.tinfirm.shared.web;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * The bean validation of a request DTO for a caller that does not come through Spring MVC's {@code @Valid}: the
 * write APIs the {@code mcp} module calls (ADR-0045) check the same constraints of {@code api/openapi.yaml} as the
 * REST controller's binding, and refuse as it does, with {@code 400 VALIDATION_FAILED} naming the fields and the
 * messages, never the rejected values.
 */
public final class RequestValidation {

    private RequestValidation() {
    }

    /** The request, or {@code VALIDATION_FAILED} with every violated constraint as {@code field: message}. */
    public static <T> T requireValid(Validator validator, T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; ")));
        }
        return request;
    }
}
