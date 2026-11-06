package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A SCIM request's body is read up to 256 KiB whatever its length says (the security review of 2026-10-27, L10); a
 * body declared longer is refused {@code 413} by the filter before it is read.
 */
class ScimBodyLimitTest {

    @Test
    void aBodyIsReadUpTo256KiBOnly() throws IOException {
        MockHttpServletRequest within = new MockHttpServletRequest("POST", "/scim/v2/Users");
        within.setContent(new byte[ScimRequestFilter.MAX_BODY_BYTES]);
        assertThat(new ScimRequestFilter.Bounded(within).getInputStream().readAllBytes())
            .hasSize(ScimRequestFilter.MAX_BODY_BYTES);

        MockHttpServletRequest beyond = new MockHttpServletRequest("POST", "/scim/v2/Users");
        beyond.setContent(new byte[ScimRequestFilter.MAX_BODY_BYTES + 1]);
        assertThatThrownBy(() -> new ScimRequestFilter.Bounded(beyond).getInputStream().readAllBytes())
            .isInstanceOf(ScimRequestFilter.BodyTooLarge.class)
            .hasMessage("The request body is larger than 256 KiB");
        MockHttpServletRequest read = new MockHttpServletRequest("POST", "/scim/v2/Users");
        read.setContent(new byte[ScimRequestFilter.MAX_BODY_BYTES + 1]);
        assertThatThrownBy(() -> new ScimRequestFilter.Bounded(read).getReader().lines().count())
            .hasRootCauseInstanceOf(ScimRequestFilter.BodyTooLarge.class);
    }

    /** Wrapped by Spring as an unreadable message, the cause is found and the answer is {@code 413}. */
    @Test
    void aBodyPastTheCapIsAnsweredWith413() {
        HttpMessageNotReadableException wrapped = new HttpMessageNotReadableException("I/O error while reading",
                new java.io.UncheckedIOException(new ScimRequestFilter.BodyTooLarge()), new MockHttpInputMessage(
                        new byte[0]));
        ResponseEntity<Map<String, Object>> answer = new ScimExceptionHandler().unreadable(wrapped);
        assertThat(answer.getStatusCode().value()).isEqualTo(413);
        assertThat(new ScimExceptionHandler().unreadable(new HttpMessageNotReadableException("bad JSON",
                new MockHttpInputMessage(new byte[0]))).getStatusCode().value()).isEqualTo(400);
    }
}
