package com.flowtivo.tinfirm.company.internal;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.shared.web.ApiException;

/** SCIM's error form (RFC 7644 §3.12), written by the endpoint's chain and its controller alike. */
final class ScimErrors {

    static final String MEDIA_TYPE = "application/scim+json";

    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ScimErrors() {
    }

    static Map<String, Object> body(ScimException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ERROR_SCHEMA));
        body.put("status", Integer.toString(ex.status()));
        if (ex.scimType() != null) {
            body.put("scimType", ex.scimType());
        }
        body.put("detail", ex.getMessage());
        return body;
    }

    /**
     * An answer of the API's rule after a commit or for want of a database connection ({@code ServerBusy}) in SCIM's
     * form: a 503 with its {@code Retry-After}, or a 500 with its detail.
     */
    static ScimException of(ApiException answer) {
        if (answer.status() == HttpStatus.SERVICE_UNAVAILABLE && answer.retryAfter() != null) {
            return ScimException.busy(answer.retryAfter().toSeconds());
        }
        return new ScimException(answer.status().value(), null, answer.getMessage());
    }

    /** Writes the error itself, from a filter, before any handler. */
    static void write(HttpServletResponse response, ScimException ex) throws IOException {
        response.setStatus(ex.status());
        if (ex.retryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        if (ex.status() == 401) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(body(ex)));
    }
}
